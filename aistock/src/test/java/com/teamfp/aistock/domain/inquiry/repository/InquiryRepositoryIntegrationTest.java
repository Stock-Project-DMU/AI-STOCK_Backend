package com.teamfp.aistock.domain.inquiry.repository;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.inquiry.entity.Inquiry;
import com.teamfp.aistock.domain.inquiry.entity.InquiryStatus;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chore/admin-entity-update — InquiryRepository 실제 MySQL 연동 테스트.
 * 사전 조건: 로컬 docker-compose(MySQL 3306)가 떠 있어야 한다.
 * @Transactional로 감싸서 테스트 종료 후 자동 롤백된다.
 */
@SpringBootTest
@Transactional
class InquiryRepositoryIntegrationTest {

    @Autowired
    private InquiryRepository inquiryRepository;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String suffix, Role role) {
        return userRepository.save(User.builder()
                .loginId("inquiry-repo-test-" + role + "-" + suffix)
                .name("문의레포테스터")
                .role(role)
                .isActive(true)
                .build());
    }

    @Test
    @DisplayName("findAllByUserIdOrderByCreatedAtDesc는 본인이 작성한 문의만, 최신순으로 반환한다")
    void findAllByUserIdOrderByCreatedAtDesc_returnsOwnInquiriesNewestFirst() throws InterruptedException {
        User owner = newUser(String.valueOf(System.nanoTime()), Role.USER);
        User other = newUser(String.valueOf(System.nanoTime()), Role.USER);

        Inquiry first = inquiryRepository.save(Inquiry.builder()
                .user(owner).title("첫 번째 문의").content("내용1").build());
        // created_at 정렬을 안정적으로 검증하기 위해 두 번째 문의 사이에 시간 간격을 둔다.
        Thread.sleep(1000);
        Inquiry second = inquiryRepository.save(Inquiry.builder()
                .user(owner).title("두 번째 문의").content("내용2").build());
        inquiryRepository.save(Inquiry.builder()
                .user(other).title("남의 문의").content("내용3").build());

        List<Inquiry> myInquiries = inquiryRepository.findAllByUserIdOrderByCreatedAtDesc(owner.getUserId());

        assertThat(myInquiries).extracting(Inquiry::getInquiryId)
                .containsExactly(second.getInquiryId(), first.getInquiryId());
    }

    @Test
    @DisplayName("findByInquiryIdAndUserId는 소유권이 일치할 때만 조회되고, 다른 사용자면 비어있다")
    void findByInquiryIdAndUserId_verifiesOwnership() {
        User owner = newUser(String.valueOf(System.nanoTime()), Role.USER);
        User stranger = newUser(String.valueOf(System.nanoTime()), Role.USER);
        Inquiry inquiry = inquiryRepository.save(Inquiry.builder()
                .user(owner).title("제목").content("내용").build());

        Optional<Inquiry> found = inquiryRepository.findByInquiryIdAndUserId(inquiry.getInquiryId(), owner.getUserId());
        Optional<Inquiry> notFound = inquiryRepository.findByInquiryIdAndUserId(inquiry.getInquiryId(), stranger.getUserId());

        assertThat(found).isPresent();
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("findAllByOrderByStatusAscCreatedAtDesc는 미답변(PENDING) 문의를 답변완료(ANSWERED)보다 먼저 반환한다")
    void findAllByOrderByStatusAscCreatedAtDesc_prioritizesPending() {
        User owner = newUser(String.valueOf(System.nanoTime()), Role.USER);
        User admin = newUser(String.valueOf(System.nanoTime()), Role.ADMIN);

        Inquiry answered = inquiryRepository.save(Inquiry.builder()
                .user(owner).title("답변완료 문의").content("내용").build());
        answered.answer("답변입니다", admin);
        inquiryRepository.save(answered);

        Inquiry pending = inquiryRepository.save(Inquiry.builder()
                .user(owner).title("미답변 문의").content("내용").build());

        List<Inquiry> all = inquiryRepository.findAllByOrderByStatusAscCreatedAtDesc();

        int pendingIndex = indexOf(all, pending.getInquiryId());
        int answeredIndex = indexOf(all, answered.getInquiryId());
        assertThat(pendingIndex).isLessThan(answeredIndex);
    }

    @Test
    @DisplayName("deleteByUserId는 해당 사용자의 문의를 전부 삭제한다 (탈퇴 처리용)")
    void deleteByUserId_removesAllInquiriesOfUser() {
        User owner = newUser(String.valueOf(System.nanoTime()), Role.USER);
        inquiryRepository.save(Inquiry.builder().user(owner).title("문의1").content("내용1").build());
        inquiryRepository.save(Inquiry.builder().user(owner).title("문의2").content("내용2").build());

        inquiryRepository.deleteByUserId(owner.getUserId());

        assertThat(inquiryRepository.findAllByUserIdOrderByCreatedAtDesc(owner.getUserId())).isEmpty();
    }

    private int indexOf(List<Inquiry> inquiries, Long inquiryId) {
        for (int i = 0; i < inquiries.size(); i++) {
            if (inquiries.get(i).getInquiryId().equals(inquiryId)) {
                return i;
            }
        }
        throw new AssertionError("리스트에서 inquiryId=" + inquiryId + "를 찾을 수 없습니다.");
    }
}
